plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Not applied anywhere: AGP 9 compiles Kotlin itself ("built-in
    // Kotlin"), but uses whichever Kotlin Gradle plugin is on the build
    // classpath. Declaring it here is what moves that from AGP's default to
    // 2.4, which StreamPack's metadata needs.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
