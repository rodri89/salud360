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

// ---- Entorno (dev = MAMP local, release = producción) ----
// Se genera `Entornos.kt` con las URLs de gradle.properties. Ver docs/DESPLIEGUE.md.
val tareasPedidas = gradle.startParameter.taskNames.joinToString(" ").lowercase()
val entornoElegido: String = (findProperty("entorno") as String?)?.trim()?.lowercase()
    ?: if (Regex("developmentrun|debug|\\bdev|:dev").containsMatchIn(tareasPedidas)) "dev" else "release"
require(entornoElegido == "dev" || entornoElegido == "release") { "-Pentorno debe ser dev o release (fue '$entornoElegido')" }
val devHost: String = (findProperty("devHost") as String?)?.trim().orEmpty()

fun propiedadEntorno(clave: String): String =
    (findProperty("salud360.entorno.$entornoElegido.$clave") as String?)?.trim()
        ?: error("Falta salud360.entorno.$entornoElegido.$clave en gradle.properties")

val hcUrls: Map<String, String> = properties.keys.map { it.toString() }
    .filter { it.startsWith("salud360.entorno.$entornoElegido.hc.") }
    .associate { it.removePrefix("salud360.entorno.$entornoElegido.hc.") to propiedadEntorno("hc." + it.removePrefix("salud360.entorno.$entornoElegido.hc.")) }

val dirEntorno = layout.buildDirectory.dir("generated/entorno/commonMain/kotlin")
val generarEntorno by tasks.registering {
    val salida = dirEntorno
    val entorno = entornoElegido
    val turnos = propiedadEntorno("turnos")
    val hcs = hcUrls
    val host = devHost
    inputs.property("entorno", entorno); inputs.property("turnos", turnos); inputs.property("hcs", hcs); inputs.property("devHost", host)
    outputs.dir(salida)
    doLast {
        val archivo = salida.get().file("com/salud360/app/Entornos.kt").asFile
        archivo.parentFile.mkdirs()
        archivo.writeText(
            """
            |package com.salud360.app
            |
            |/** Generado por Gradle a partir de gradle.properties (-Pentorno=$entorno). No editar a mano. */
            |object Entornos {
            |    const val ENTORNO = "$entorno"
            |    const val TURNOS_URL = "$turnos"
            |    /** Host local forzado con -PdevHost (vacío = el de cada plataforma). */
            |    const val DEV_HOST = "$host"
            |    val HC_URLS: Map<String, String> = mapOf(${hcs.entries.joinToString { "\"${it.key}\" to \"${it.value}\"" }})
            |}
            |""".trimMargin(),
        )
    }
}
logger.lifecycle("Salud 360: entorno '$entornoElegido' (turnos: ${propiedadEntorno("turnos")})")

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
        commonMain { kotlin.srcDir(generarEntorno) }
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

// Atajos: `./gradlew devWeb` (contra MAMP local) y `./gradlew releaseWeb` (build de producción).
tasks.register("devWeb") { group = "salud360"; description = "Web en http://localhost:8080 contra el MAMP local"; dependsOn("wasmJsBrowserDevelopmentRun") }
tasks.register("releaseWeb") { group = "salud360"; description = "Build web de producción (composeApp/build/dist/wasmJs/productionExecutable)"; dependsOn("wasmJsBrowserDistribution") }
