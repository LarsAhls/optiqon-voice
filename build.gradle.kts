plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    // Put on the classpath here; app/build.gradle.kts applies it only when
    // google-services.json exists on this machine.
    alias(libs.plugins.google.services) apply false
}
