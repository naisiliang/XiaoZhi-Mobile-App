plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lchuang.xiaozhimobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lchuang.xiaozhimobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")
}
