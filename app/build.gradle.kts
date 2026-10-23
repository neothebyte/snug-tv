plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "tv.livingroom.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "tv.livingroom.watch"
        minSdk = 24
        targetSdk = 35
        versionCode = 19
        versionName = "1.13.0"
    }

    // Release key: from ~/.gradle/gradle.properties locally, or environment variables in CI.
    // Without it, release builds fall back to the debug key.
    fun signingValue(name: String): String? =
        providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull

    val releaseKeystore = signingValue("LIVINGROOM_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("LIVINGROOM_KEYSTORE_PASSWORD")
                keyAlias = signingValue("LIVINGROOM_KEY_ALIAS") ?: "livingroom"
                keyPassword = signingValue("LIVINGROOM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 makes Compose noticeably quicker on TV boxes. Names are kept (see proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Installs the startup profile (src/main/baseline-prof.txt) on sideloaded installs too.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
