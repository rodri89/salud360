plugins { id("salud360.kmp.library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:database"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.json)
            api(libs.multiplatform.settings)
            api(libs.koin.core)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        wasmJsMain.dependencies { implementation(libs.ktor.client.js) }
    }
}
