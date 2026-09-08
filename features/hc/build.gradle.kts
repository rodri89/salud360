plugins { id("salud360.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:data"))
            api(project(":core:ui"))
        }
    }
}

kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

kotlin {
    sourceSets {
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
