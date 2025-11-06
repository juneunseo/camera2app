plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.camera2app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.camera2app"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // ★ Java/Kotlin 타깃을 17로 '둘 다' 고정
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 필요 시 아래 주석 해제 (구버전 API용 desugaring)
        // isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Kotlin 2.x 권장 설정
    kotlin {
        jvmToolchain(17)
    }

    buildFeatures { viewBinding = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    // desugaring 켰다면 같이 추가
    // coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
