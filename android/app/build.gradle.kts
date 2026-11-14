plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.orbitflags.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.orbitflags.app"
        minSdk = 29
        targetSdk = 35
        // Phones are arm64 (older ones armeabi-v7a); x86_64 is for emulators.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        versionCode = 10
        versionName = "0.8.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // The model file is memory-mapped, so it must be stored uncompressed.
    androidResources { noCompress += "tflite" }
}
dependencies {
    implementation(project(":core"))
    // On-device text recognition with a bundled model (no model download).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Runs the bundled adult-image model on the device.
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    // Runs the bundled sentence-embedding model for the text classifier.
    implementation("com.google.mediapipe:tasks-text:0.10.26.1")
}
