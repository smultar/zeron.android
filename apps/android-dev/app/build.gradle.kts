plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Rust core (`scripts/android/build-core.sh`) drops the UniFFI Kotlin
// bindings and the per-ABI .so files here.
val core = rootProject.file("../../target/android-core")

android {
    namespace = "sh.zeron.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "sh.zeron.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.113"
        // Phones are arm64; `-Pabis=x86_64` (or `arm64-v8a,x86_64`) adds the emulator's ABI.
        val abis = (project.findProperty("abis") as String?)?.split(",") ?: listOf("arm64-v8a")
        ndk { abiFilters += abis }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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

    sourceSets["main"].java.srcDir(core.resolve("kotlin"))
    sourceSets["main"].jniLibs.srcDir(core.resolve("jniLibs"))
    // Compressed native libs: a much smaller APK (Android unpacks them at install).
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // Android Auto: a messaging screen in the car (conversations read aloud, spoken replies).
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // UniFFI's Kotlin bindings load the core through JNA.
    implementation("net.java.dev.jna:jna:5.15.0@aar")
    // Tool and file icons ship as SVG (shared with iOS).
    implementation("com.caverock:androidsvg-aar:1.4")
    // Voice calls: the phone is the audio endpoint of the host's Codex voice session (WebRTC).
    implementation("io.getstream:stream-webrtc-android:1.3.10")
}
