import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.photovault.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photovault.app"
        minSdk = 24
        targetSdk = 34

        // versionCode 跟构建时间走（每次编译都不一样，保证能覆盖安装），
        // versionName 只在发版时手动改，跟服务端版本号对齐。
        //
        // 注意两个坑：
        //  1. 这里不能写 java.util.Date —— .kts 里的 `java` 是 Gradle 的扩展，会冲突
        //  2. versionCode 上限是 Int.MAX_VALUE(21 亿)，`yyMMddHHmm` 会到 26 亿直接溢出，
        //     toIntOrNull() 返 null 后静默变成 1。所以只取到小时（8 位，最多 99123123）
        val now = Date()
        versionCode = SimpleDateFormat("yyMMddHH", Locale.US).format(now).toIntOrNull() ?: 1
        // 对外版本号跟服务端同一套 2.x 线；构建时间另存 BUILD_TIME，装到手机上仍能分清是哪个包
        versionName = "2.0.2"

        buildConfigField("String", "BUILD_TIME",
            "\"" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now) + "\"")
    }

    signingConfigs {
        // 固定使用用户目录下的标准 debug keystore。
        // 之前 CI/沙箱把 ANDROID_USER_HOME 指到临时目录，每次构建都会生成新 key，
        // 结果新 APK 和手机上已装版本的签名对不上，只能卸载重装。
        getByName("debug") {
            val debugKs = File(System.getProperty("user.home"), ".android/debug.keystore")
            if (debugKs.exists()) {
                storeFile = debugKs
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        val ks = rootProject.file("release.keystore")
        if (ks.exists()) {
            create("release") {
                storeFile = ks
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // 装到手机上的就是这个包：开 R8 + 资源裁剪，把没用的图标/代码剔掉
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 有 release.keystore 才签名；没有就保持未签名，避免把调试签名当正式包
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    // Compose（BOM 统一版本）
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 后台任务
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 网络 & 图片
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // 内置视频播放器（不再甩给系统播放器）
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // 存储
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.core:core-ktx:1.13.1")
}
