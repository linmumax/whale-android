plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whale.pet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whale.pet"
        minSdk = 33
        targetSdk = 35
        versionCode = 27
        versionName = "1.0.17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // WebView 走 android_asset 读取 webm，保持不压缩以减少解析开销
        noCompress += "webm"
    }
    lint {
        // 纯中文应用，界面文案直接写在 Kotlin 里，不需要抽到 strings.xml
        disable += "SetTextI18n"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
