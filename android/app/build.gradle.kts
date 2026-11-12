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
        versionCode = 4
        versionName = "0.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core"))
}
