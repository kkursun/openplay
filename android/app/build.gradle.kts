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
        // Releases name their version, like v1.2 or v1.2-beta (see .github/workflows/android-release.yml).
        val tag = Regex("v((\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-[\\w.]+)?)").matchEntire(System.getenv("OPENPLAY_VERSION") ?: "")
        versionName = tag?.groupValues?.get(1) ?: "1.0"
        versionCode = tag?.groupValues?.drop(2)?.map { it.toIntOrNull() ?: 0 }?.let { (a, b, c) -> a * 10000 + b * 100 + c } ?: 10000
    }

    // An APK per processor type, a third the size of one with all of libtorrent's native code (plus that one).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64") // the ones libtorrent4j is built for
            isUniversalApk = true
        }
    }

    // A key of your own, to sign every release the same way (Android only updates an app with the key it was
    // installed with): OPENPLAY_KEYSTORE is its file, OPENPLAY_KEY_PASSWORD its password, alias "openplay".
    // Without one, the building machine's debug key signs, which is fine for installing by hand.
    val keystore = System.getenv("OPENPLAY_KEYSTORE")?.takeIf { it.isNotEmpty() }
    signingConfigs {
        if (keystore != null) create("own") {
            storeFile = file(keystore)
            storePassword = System.getenv("OPENPLAY_KEY_PASSWORD")
            keyAlias = "openplay"
            keyPassword = System.getenv("OPENPLAY_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystore != null) "own" else "debug")
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
    packaging {
        jniLibs.useLegacyPackaging = true // installed as files, so airmirror can be run
    }
}

// airmirror (the repository's airmirror/: AirPlay screen mirroring, doubletake's sender) for the app to run. Android
// only lets an app run programs it installs as native libraries, hence the name. Needs Go.
// ponytail: arm64 only, which Go builds without the NDK; phones of other ABIs mirror over HLS
val airmirror = tasks.register<Exec>("airmirror") {
    val src = rootProject.file("../airmirror")
    val out = file("src/main/jniLibs/arm64-v8a/libairmirror.so")
    inputs.files(fileTree(src) { include("**/*.go", "go.mod", "go.sum") })
    outputs.file(out)
    workingDir = src
    environment(mapOf("GOOS" to "android", "GOARCH" to "arm64", "CGO_ENABLED" to "0"))
    commandLine("go", "build", "-trimpath", "-ldflags=-s -w", "-o", out.path, ".")
}
tasks.named("preBuild") { dependsOn(airmirror) }

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
