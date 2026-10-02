plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.syntaxcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.syntaxcam"
        minSdk = 29
        targetSdk = 34
        versionCode = 7
        versionName = "1.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so the APK installs directly. Use your own keystore for Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false }
}

dependencies {
    val camerax = "1.5.1"   // 1.5+ adds RAW (DNG) capture
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
}
