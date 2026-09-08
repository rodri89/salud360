import org.jetbrains.compose.ComposeExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Plugin de convención para módulos con pantallas Compose Multiplatform.
 * Extiende `salud360.kmp.library` agregando Compose, navegación, ViewModel y Koin.
 */
plugins {
    id("salud360.kmp.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val composeDeps = extensions.getByType<ComposeExtension>().dependencies

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets {
        commonMain.dependencies {
            implementation(composeDeps.runtime)
            implementation(composeDeps.foundation)
            implementation(composeDeps.material3)
            implementation(composeDeps.ui)
            implementation(composeDeps.components.resources)
            implementation(composeDeps.materialIconsExtended)
            implementation(libs.findLibrary("lifecycle-viewmodel-compose").get())
            implementation(libs.findLibrary("lifecycle-runtime-compose").get())
            implementation(libs.findLibrary("navigation-compose").get())
            implementation(libs.findLibrary("koin-core").get())
            implementation(libs.findLibrary("koin-compose").get())
            implementation(libs.findLibrary("koin-compose-viewmodel").get())
        }
    }
}
