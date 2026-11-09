plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.claudelinux"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.claudelinux"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        // proot must exist as a real file in nativeLibraryDir: Android forbids exec from app data.
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libproot*.so" }
    }
}
dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")
}
