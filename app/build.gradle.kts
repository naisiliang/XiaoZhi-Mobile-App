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
        versionCode = 12
        versionName = "0.6.5"

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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.k2-fsa:sherpa-onnx:v1.13.4")
    implementation("com.belerweb:pinyin4j:2.5.1")
    testImplementation("junit:junit:4.13.2")
}
