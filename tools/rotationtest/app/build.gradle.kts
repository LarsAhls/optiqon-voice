plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// versionCode comes from the command line so one source tree yields v1, v2, v3 for the
// install / update / negative-update steps of the matrix.
val rotVersionCode = (project.findProperty("rotVersionCode") as String?)?.toInt() ?: 1

android {
    namespace = "se.optiqon.rotationtest"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.optiqon.rotationtest"
        minSdk = 26
        targetSdk = 35
        versionCode = rotVersionCode
        versionName = "rot-$rotVersionCode"
    }

    buildTypes {
        release {
            // Always unsigned: the matrix script signs every variant itself with apksigner.
            signingConfig = null
            isMinifyEnabled = false
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.androidx.security.crypto)
}
