import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/**
 * Plugin de convención para librerías Kotlin Multiplatform de Salud 360.
 * Configura los targets Android (plugin KMP de AGP 9), iOS, Web (wasmJs) y JVM (para el servidor).
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(17)

    (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
        namespace = "com.salud360." + project.path.removePrefix(":").replace(":", ".").replace("-", "")
        compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
    }

    iosArm64()
    iosSimulatorArm64()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    // El servidor reutiliza los módulos de dominio y base de datos
    jvm()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
            implementation(libs.findLibrary("kotlinx-datetime").get())
            implementation(libs.findLibrary("kermit").get())
        }
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
