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
        jvmTest.dependencies { implementation(libs.sqldelight.sqlite) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        wasmJsMain.dependencies { implementation(libs.ktor.client.js) }
    }
}

// `HcPediatriaE2ETest` corre contra una API de pediatría de verdad (el MAMP en desarrollo). Se saltea
// sola si no se le pasan estas propiedades, así el `jvmTest` de siempre sigue sin necesitar servidor.
tasks.withType<Test>().configureEach {
    listOf(
        "salud360.test.hc.pediatria", "salud360.test.hc.token", "salud360.test.hc.paciente",
        "salud360.test.hc.dni", "salud360.test.hc.nombre", "salud360.test.hc.apellido",
    ).forEach { clave -> providers.gradleProperty(clave).orNull?.let { systemProperty(clave, it) } }
}
