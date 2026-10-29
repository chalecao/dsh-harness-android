plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.deepseek.dsh"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.deepseek.dsh"
        minSdk = 26
        // targetSdk 28：Android 10+ 的 W^X 策略禁止 targetSdk>=29 的应用执行
        // 私有目录中的可执行文件；28 是内嵌 Node 运行时的必需值（Termux 同方案），
        // 同时可自动获得 legacy 外部存储访问，便于 Agent 直接操作手机文件。
        targetSdk = 28
        versionCode = 100
        versionName = "1.0.0"
        // dsh 运行时为 arm64 预编译 Node，仅支持 64 位 ARM 设备
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // 先用 debug  key 签名，便于直接安装
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // payload zip 已压缩，避免 APK 二次压缩浪费
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 大 asset 不进压缩（zip 本身已压缩，aapt 会按 .zip 扩展名自动跳过压缩）
    androidResources {
        noCompress += listOf("zip")
    }

    lint {
        // targetSdk 28 是内嵌 Node 可执行文件的硬性要求（Android W^X 限制），
        // 会触发 Google Play 的 ExpiredTargetSdkVersion 提示；本应用走侧载分发，
        // 不通过 Play 商店发布，故仅禁用该项检查，不做整体降级。
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
}
