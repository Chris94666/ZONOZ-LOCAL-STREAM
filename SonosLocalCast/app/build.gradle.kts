import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ------------------------------------------------------------------ 签名
// 公开仓库里不放 keystore，也不写死口令。
// 优先读 local.properties（已在 .gitignore 里），其次环境变量，最后才退回工程内的示例文件。
// 想打 release 包但没有 keystore 时，构建产物会是未签名的 APK（可直接用 debug 构建来跑）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingProp(key: String, env: String, fallback: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(env) ?: fallback

val selfKeystore = file(
    signingProp("signing.storeFile", "ZONOZ_STORE_FILE", "../keystore/sonoscast.jks")
)

android {
    namespace = "com.sonoscast.local"
    compileSdk = 35

    signingConfigs {
        if (selfKeystore.exists()) {
            // 口令**不能**有硬编码回退：本文件是公开的。
            // 本地放在 local.properties（已被 .gitignore 忽略），CI 用环境变量。
            val storePass = signingProp("signing.storePassword", "ZONOZ_STORE_PASSWORD")
            val alias = signingProp("signing.keyAlias", "ZONOZ_KEY_ALIAS")
            val keyPass = signingProp("signing.keyPassword", "ZONOZ_KEY_PASSWORD")
            if (storePass.isBlank() || alias.isBlank() || keyPass.isBlank()) {
                throw GradleException(
                    "缺少签名口令：请在 SonosLocalCast/local.properties 里配置 " +
                        "signing.storePassword / signing.keyAlias / signing.keyPassword，" +
                        "或设置同名环境变量 ZONOZ_STORE_PASSWORD / ZONOZ_KEY_ALIAS / ZONOZ_KEY_PASSWORD"
                )
            }
            create("self") {
                storeFile = selfKeystore
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    defaultConfig {
        applicationId = "com.sonoscast.local"
        minSdk = 26          // Android 8.0
        targetSdk = 35
        // 对外版本从 1.0 起算（首个开源版本）；versionCode 保持单调递增，
        // 这样老用户能正常覆盖安装。
        versionCode = 56
        versionName = "1.2"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            // 默认 AGP 会在 %USERPROFILE%\.android 生成 debug.keystore 并加锁；
            // 在受限/沙箱环境里那个目录不可写，assembleDebug 会直接失败。
            // 这里让 debug 也复用工程自带 keystore（sidestep 对用户目录的依赖）。
            if (selfKeystore.exists()) {
                signingConfig = signingConfigs.getByName("self")
            }
        }
        release {
            // R8：既缩减体积也做基础混淆。Compose / OkHttp / NanoHTTPD 的保留规则见 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // keystore 缺失时退回 unsigned（不会让 sync 直接失败）
            if (selfKeystore.exists()) {
                signingConfig = signingConfigs.getByName("self")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    lint {
        // NanoHTTPD 使用了部分已过时 API；开源版仍把严重问题当警告，不阻断构建
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM 统一版本
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // 本地 HTTP 媒体服务器（Range 支持由我们自己实现）
    implementation(libs.nanohttpd)

    // SOAP over HTTP
    implementation(libs.androidx.media)
    implementation(libs.okhttp)
}
