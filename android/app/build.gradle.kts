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

        // 版本号跟着构建时间走：每次编译都不一样，装到手机上能在系统设置里
        // 确认装的到底是哪一次编出来的包（固定 1.0.0 时根本分不清）
        //
        // 注意两个坑：
        //  1. 这里不能写 java.util.Date —— .kts 里的 `java` 是 Gradle 的扩展，会冲突
        //  2. versionCode 上限是 Int.MAX_VALUE(21 亿)，`yyMMddHHmm` 会到 26 亿直接溢出，
        //     toIntOrNull() 返 null 后静默变成 1。所以只取到小时（8 位，最多 99123123），
        //     分钟级的区别交给 versionName 表达
        val now = Date()
        versionCode = SimpleDateFormat("yyMMddHH", Locale.US).format(now).toIntOrNull() ?: 1
        versionName = "1.1.0+" + SimpleDateFormat("MMdd.HHmm", Locale.US).format(now)

        buildConfigField("String", "BUILD_TIME",
            "\"" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now) + "\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // 存储
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.core:core-ktx:1.13.1")
}
