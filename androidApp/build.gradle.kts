/**
 * Aplicación Android. Solo contiene el punto de entrada (Application/Activity, manifiesto,
 * íconos); toda la UI y la lógica están en `:composeApp` y los módulos compartidos.
 */
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.salud360.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.salud360.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.android)
}
