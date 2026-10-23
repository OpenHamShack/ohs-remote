import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.openhamshack.remote"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.openhamshack.remote"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // R8 on: an unshrunk build carries ~41 MB of dex (all of Compose,
        // material3 and coroutines), and a Fire TV opens a freshly installed
        // APK's dex without any ahead-of-time compilation (Fire OS skips
        // dexopt at install; `dumpsys package dexopt` says run-from-apk until
        // the idle job gets to it). Reading and verifying that much dex on its
        // four slow cores was 3.2 s of a 7.3 s cold start. Keep rules for the
        // JNI libraries are in proguard-rules.pro.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The release build signed with the debug key, for the devices that
        // already run a debug build: Brian's TVs and tablets hold their
        // pairing in the app's data, so they must be updated with
        // `adb install -r` by the same signer, never uninstalled. Not
        // debuggable, which is most of its speed: ART runs a debuggable app's
        // code with its JIT in debug mode. A debuggable build is still the
        // one for development (RxPlayer's stats line and vlc-options.txt,
        // DebugAdvertiser); this one is what goes on a device to be used.
        create("fast") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // libVLC and srtdroid each carry native libraries for four ABIs; a
    // universal APK is ~150 MB. One APK per ABI as well, so a phone can be
    // given the arm64 one; the universal one is what an emulator or an
    // unknown tablet takes.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        // Dex stored uncompressed (and page-aligned), as AGP does by itself
        // from minSdk 28: ART can then map it straight out of the APK where
        // it would otherwise inflate every classes*.dex into memory at each
        // start - which is every start on a Fire TV until the APK has been
        // compiled (see buildTypes). The APK grows by the dex's compression.
        dex {
            useLegacyPackaging = false
        }
        jniLibs {
            // Both libVLC and srtdroid ship the NDK's C++ runtime.
            pickFirsts += "**/libc++_shared.so"
            // libVLC loads its plugins by path; extracted libraries are the
            // arrangement it is tested with.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // HardcodedText and SetTextI18n are warnings by default; I18N.md §3.1
    // has them as errors. The app is Compose with no XML layouts today, so
    // they guard the day a layout or a TextView appears; the Compose side
    // is shack-i18n-check's job (i18nCheck below).
    lint {
        lintConfig = file("lint.xml")
    }
}

// The family's string check (docs/I18N.md) over the whole repo - this
// app, the Apple apps beside it, the catalogs and the baseline - before any
// unit test, so `./gradlew testDebugUnitTest` (what docs/TESTING.md has
// every change run) fails on a new user-visible literal. A task here rather
// than a JUnit test because the check is Python and scans files outside
// the module; python3 3.11 or later must be on the PATH (tomllib).
val i18nCheck = tasks.register<Exec>("i18nCheck") {
    group = "verification"
    description = "Runs shack-i18n-check with the repo's baseline and allow-list (tools/i18n.py check)."
    workingDir = rootProject.projectDir.parentFile
    commandLine("python3", "tools/i18n.py", "check")
}
tasks.withType<Test>().configureEach { dependsOn(i18nCheck) }
tasks.named("check") { dependsOn(i18nCheck) }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    // Writes the APK's baseline profile (src/main/baseline-prof.txt and the
    // libraries') where ART compiles from, for an APK that did not come from
    // Play. Compose depends on it already; declared so that it stays.
    implementation(libs.androidx.profileinstaller)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libvlc)
    implementation(libs.streampack.core)
    implementation(libs.streampack.srt)
    implementation(libs.streampack.ui)
    implementation(libs.okhttp)
    // OpenHamShack Icom (and, through it, :core-candidates and ShackFormat).
    implementation(project(":kind-stationflow"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The Icom and Flex clients against a recording server (bearer header, nothing that transmits).
    testImplementation(libs.okhttp.mockwebserver)
    // android.jar's org.json is a stub on the JVM; the real one for tests.
    testImplementation(libs.org.json)
}
