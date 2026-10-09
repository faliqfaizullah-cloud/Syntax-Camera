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
        versionCode = 18
        versionName = "2.5.0"
    }

    // Permanent release key (from GitHub secrets) so every new APK updates the installed app in place.
    val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")
    val hasReleaseKey = releaseKeystore != null && File(releaseKeystore).exists()
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(releaseKeystore!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Falls back to the debug key when no release key is configured.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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
