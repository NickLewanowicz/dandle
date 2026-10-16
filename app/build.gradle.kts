plugins {
    alias(libs.plugins.android.application) apply false
    // KSP (Room) requires KGP opt-out of AGP 9 built-in Kotlin; see app/gradle.properties.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
