plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.kkursun.openplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kkursun.openplay"
        minSdk = 29 // Android 10: capturing other apps' sound while mirroring
        // 37 would also need ACCESS_LOCAL_NETWORK (Android 17's permission for reaching TVs and the PC)
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") // the ones libtorrent4j is built for
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Installed by hand, not from a store: signed with the building machine's debug key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        disable += "OldTargetApi" // see targetSdk
    }
}

val libtorrent = "2.1.0-39"

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // BitTorrent, with libtorrent's native code for phones (arm) and emulators (x86_64)
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814") // Android's own org.json is only stubs off a phone
}
