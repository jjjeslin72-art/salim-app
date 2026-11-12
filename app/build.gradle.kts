plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.juya.salim"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.juya.salim"
        minSdk = 26
        targetSdk = 36   // Google Play: 새 앱·업데이트는 API 36 이상
        // 빌드할 때마다 번호가 자동으로 올라감 (Play 스토어는 올릴 때마다 더 큰 번호가 필요)
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.0"
    }

    // 테스트 APK가 매번 같은 서명을 갖도록 고정 키 사용 (그래야 위에 덮어서 업데이트됨)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // 스토어 출시용 서명 (키는 저장소에 없고 GitHub 비밀값에서 빌드할 때만 꺼내 씀)
        val ksPath = System.getenv("UPLOAD_KEYSTORE")
        if (ksPath != null && file(ksPath).exists()) {
            create("upload") {
                storeFile = file(ksPath)
                storePassword = System.getenv("UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
