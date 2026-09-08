plugins {
    id("salud360.kmp.library")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(libs.sqldelight.runtime)
            api(libs.sqldelight.coroutines)
            api(libs.sqldelight.async)
        }
        androidMain.dependencies { implementation(libs.sqldelight.android) }
        iosMain.dependencies { implementation(libs.sqldelight.native) }
        jvmMain.dependencies { implementation(libs.sqldelight.sqlite) }
        wasmJsMain.dependencies {
            implementation(libs.sqldelight.web)
            implementation(libs.kotlinx.browser)
            implementation(npm("@cashapp/sqldelight-sqljs-worker", libs.versions.sqldelight.get()))
            implementation(npm("sql.js", "1.13.0"))
        }
    }
}

sqldelight {
    databases {
        create("Salud360Db") {
            packageName.set("com.salud360.core.database")
            // Necesario para el driver web (worker asíncrono); los demás drivers usan Schema.synchronous()
            generateAsync.set(true)
            verifyMigrations.set(false)
        }
    }
}
