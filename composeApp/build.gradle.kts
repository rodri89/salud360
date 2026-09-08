import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/**
 * Módulo compartido de la aplicación: navegación, inyección de dependencias y puntos de entrada
 * para iOS (framework `ComposeApp`) y Web (ejecutable wasmJs). La app Android vive en `:androidApp`.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
        namespace = "com.salud360.app"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("salud360")
        browser {
            commonWebpackConfig {
                outputFileName = "salud360.js"
            }
        }
        binaries.executable()
    }

    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.materialIconsExtended)
            implementation(libs.navigation.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.kotlinx.coroutines.core)

            api(project(":core:model"))
            api(project(":core:database"))
            api(project(":core:data"))
            api(project(":core:ui"))
            api(project(":features:auth"))
            api(project(":features:pacientes"))
            api(project(":features:hc"))
            api(project(":features:turnos"))
            api(project(":features:admin"))
            api(project(":especialidades:clinica"))
            api(project(":especialidades:pediatria"))
            api(project(":especialidades:gineco"))
            api(project(":especialidades:cardiologia"))
            api(project(":especialidades:endocrinologia"))
            api(project(":especialidades:hematologia"))
            api(project(":especialidades:desarrollo-infantil"))
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(devNpm("copy-webpack-plugin", "12.0.2"))
        }
    }
}
