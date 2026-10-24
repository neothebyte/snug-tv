plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "tv.livingroom.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "tv.livingroom.home"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
    }

    // Signed with the same release key as Snug, so the two can share the top shelf.
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
}

dependencies {
    implementation(project(":core-ui"))
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // The camera tile: go2rtc's RTSP streams.
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
