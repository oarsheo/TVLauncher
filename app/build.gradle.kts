import java.util.Properties

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.arshe.tvos"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.arshe.tvos"
        minSdk = 30
        targetSdk = 34
        versionCode = 12
        versionName = "0.12"
        // OTA updates: GitHub repo with releases tagged v<versionCode>; token only needed for a private repo.
        buildConfigField("String", "UPDATE_REPO", "\"${localProps.getProperty("update.repo", "oarsheo/TVLauncher")}\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"${localProps.getProperty("github.token", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal sideload build: sign with the debug key so it can be installed over adb.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.tvprovider:tvprovider:1.1.0")
    // Applies the bundled baseline profiles (Compose's + app/src/main/baseline-prof.txt) on sideloaded installs.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
