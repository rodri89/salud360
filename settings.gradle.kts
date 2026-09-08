pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "salud360"

// Núcleo compartido por todas las plataformas y por el servidor
include(":core:model")
include(":core:database")
include(":core:data")
include(":core:ui")

// Funcionalidades transversales (médicos, secretarias, administrador)
include(":features:auth")
include(":features:pacientes")
include(":features:hc")
include(":features:turnos")
include(":features:admin")

// Historias clínicas por especialidad (cada una es un módulo enchufable)
include(":especialidades:clinica")
include(":especialidades:pediatria")
include(":especialidades:gineco")
include(":especialidades:cardiologia")
include(":especialidades:endocrinologia")
include(":especialidades:hematologia")
include(":especialidades:desarrollo-infantil")

// Aplicación (Android, iOS, Web) y servidor de sincronización
include(":composeApp")
include(":androidApp")
include(":server")
