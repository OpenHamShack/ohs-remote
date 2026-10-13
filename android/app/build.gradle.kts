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
        release {
            isMinifyEnabled = false
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
